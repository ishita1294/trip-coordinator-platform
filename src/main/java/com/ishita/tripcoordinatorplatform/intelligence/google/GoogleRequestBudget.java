package com.ishita.tripcoordinatorplatform.intelligence.google;

import com.ishita.tripcoordinatorplatform.intelligence.explore.ProviderUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Keeps the number of Google calls small. Each Explore request gets its own allowance (47 calls by default), and
 * all requests together share an hourly allowance (120 calls by default) so a runaway loop cannot flood Google.
 * Once an allowance is used up, further calls are refused and Explore reports its results as incomplete.
 * <p>
 * Within one request the same Google question is asked only once, and the answer is shared with every part of
 * that request that needs it. Nothing Google returns is kept after the request ends.
 */
@Component
public class GoogleRequestBudget {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GoogleRequestBudget.class);
    private static final int WINDOW_SECONDS = 3600;

    private final int requestLimit;
    private final int hourlyLimit;
    private final Clock clock;
    private final ThreadLocal<Scope> scopes = new ThreadLocal<>();
    private Instant windowStarted;
    private int hourlyCalls;

    @Value("${app.explore.parallel-evaluations:3}")
    private int parallelism = 3;

    /** Everything we track for one request: its call count, shared answers and timings. */
    private static final class Scope {
        final String kind;
        final Long actor;
        final long started = System.nanoTime();
        final UUID requestId = UUID.randomUUID();
        final Map<Instant, Instant> departures = new HashMap<>();
        final Map<String, Integer> operations = new TreeMap<>();
        final Map<String, Long> durations = new TreeMap<>();
        final ConcurrentMap<Object, CompletableFuture<Object>> facts = new ConcurrentHashMap<>();
        int calls;
        int reused;

        Scope(String kind, Long actor) {
            this.kind = kind;
            this.actor = actor;
        }
    }

    /** What the screen and the log report about one request: how many calls were made and what is left. */
    public record Usage(
            int requestCalls,
            int requestLimit,
            int hourlyCalls,
            int hourlyLimit,
            Instant hourlyResetsAt,
            int reusedCalls,
            Map<String, Integer> operations,
            Map<String, Long> stageMillis,
            UUID requestId) {
    }

    @Autowired
    public GoogleRequestBudget(
            @Value("${app.explore.google.max-calls-per-request:47}") int requestLimit,
            @Value("${app.explore.google.max-calls-per-hour:120}") int hourlyLimit) {
        this(requestLimit, hourlyLimit, Clock.systemUTC());
    }

    /** Lets tests supply their own clock so the hourly window can be moved forward without waiting. */
    GoogleRequestBudget(int requestLimit, int hourlyLimit, Clock clock) {
        if (requestLimit < 1 || hourlyLimit < 1) {
            throw new IllegalArgumentException("Google call limits must be positive");
        }
        this.requestLimit = requestLimit;
        this.hourlyLimit = hourlyLimit;
        this.clock = clock;
        this.windowStarted = clock.instant();
    }

    public void begin() {
        begin("explore");
    }

    public void begin(String kind) {
        begin(kind, null);
    }

    /** Starts a new request allowance on this thread. Call {@link #end()} when the request is finished. */
    public void begin(String kind, Long actor) {
        scopes.set(new Scope(kind, actor));
    }

    /** Google rejects departure times in the past, so earlier requests are moved to two seconds from now. */
    public synchronized Instant departureAt(Instant requested) {
        var scope = scopes.get();
        if (scope == null) {
            return notBeforeSoon(requested);
        }
        return scope.departures.computeIfAbsent(requested, this::notBeforeSoon);
    }

    private Instant notBeforeSoon(Instant requested) {
        var earliest = clock.instant().plusSeconds(2);
        return requested.isBefore(earliest) ? earliest : requested;
    }

    /** Ends the request on this thread: logs how many calls it used and drops the shared answers. */
    public void end() {
        var scope = scopes.get();
        if (scope != null) {
            var usage = usage();
            log.info("EXPLORE {} completed in {} ms: {} Google attempts, {} reused; operations={}, stageMillis={}, "
                    + "hourly={}/{}, resetsAt={}, requestId={}",
                    scope.kind, (System.nanoTime() - scope.started) / 1_000_000, usage.requestCalls(),
                    usage.reusedCalls(), usage.operations(), usage.stageMillis(), usage.hourlyCalls(),
                    usage.hourlyLimit(), usage.hourlyResetsAt(), scope.requestId);
            scope.facts.clear();
        }
        scopes.remove();
    }

    /** Starts a fresh hourly count once an hour has passed. */
    private void resetWindow() {
        if (!clock.instant().isBefore(windowStarted.plusSeconds(WINDOW_SECONDS))) {
            windowStarted = clock.instant();
            hourlyCalls = 0;
        }
    }

    /** How many more Google calls this request may make, taking the smaller of the request and hourly allowances. */
    public synchronized int remaining() {
        resetWindow();
        var scope = scopes.get();
        int usedByRequest = scope == null ? 0 : scope.calls;
        return Math.min(requestLimit - usedByRequest, hourlyLimit - hourlyCalls);
    }

    synchronized void consume() {
        consume("unspecified");
    }

    /** Counts one Google call, or refuses it when the hourly or per-request allowance is already used up. */
    private synchronized void consume(String operation) {
        resetWindow();
        var scope = scopes.get();
        if (hourlyCalls >= hourlyLimit) {
            throw new ProviderUnavailableException("Google application hourly call budget reached (" + hourlyLimit
                    + " attempts across all users/searches/comparisons). Results are incomplete; resets at "
                    + windowStarted.plusSeconds(WINDOW_SECONDS) + ".");
        }
        if (scope != null && scope.calls >= requestLimit) {
            throw new ProviderUnavailableException("Google per-request call budget reached (" + requestLimit
                    + " attempts). Results are incomplete; deferred candidates need a separate request.");
        }
        hourlyCalls++;
        if (scope != null) {
            scope.calls++;
            scope.operations.merge(operation, 1, Integer::sum);
        }
    }

    /** A snapshot of the calls used so far by the request on this thread, and of the shared hourly count. */
    public synchronized Usage usage() {
        resetWindow();
        var scope = scopes.get();
        return new Usage(
                scope == null ? 0 : scope.calls,
                requestLimit,
                hourlyCalls,
                hourlyLimit,
                windowStarted.plusSeconds(WINDOW_SECONDS),
                scope == null ? 0 : scope.reused,
                scope == null ? Map.of() : Map.copyOf(scope.operations),
                scope == null ? Map.of() : Map.copyOf(scope.durations),
                scope == null ? null : scope.requestId);
    }

    /** Runs the work and adds the time it took under the given stage name, for the request's timing report. */
    public <T> T timed(String stage, Supplier<T> work) {
        long start = System.nanoTime();
        try {
            return work.get();
        } finally {
            synchronized (this) {
                var scope = scopes.get();
                if (scope != null) {
                    scope.durations.merge(stage, (System.nanoTime() - start) / 1_000_000, Long::sum);
                }
            }
        }
    }

    /**
     * Makes one Google call that counts against the allowances. If the same question (same identity) was already
     * asked during this request, the earlier answer is reused and no new call is counted.
     */
    @SuppressWarnings("unchecked")
    <T> T call(String operation, Object identity, Supplier<T> work) {
        var scope = scopes.get();
        if (scope == null) {
            begin("provider");
            try {
                return call(operation, identity, work);
            } finally {
                end();
            }
        }
        var pending = new CompletableFuture<Object>();
        var previous = scope.facts.putIfAbsent(identity, pending);
        if (previous != null) {
            synchronized (this) {
                scope.reused++;
            }
            return (T) await(previous);
        }
        try {
            consume(operation);
            T value = timed(operation, work);
            pending.complete(value);
            return value;
        } catch (RuntimeException | Error failure) {
            pending.completeExceptionally(failure);
            throw failure;
        }
    }

    /** The explanation shown when Explore stops early because an allowance ran out. */
    public String stoppedMessage() {
        var usage = usage();
        if (usage.hourlyCalls() >= usage.hourlyLimit()) {
            return "Application-wide hourly Google allowance exhausted; resets at " + usage.hourlyResetsAt()
                    + ". Unevaluated places are not verified recommendations.";
        }
        return "Per-request Google allowance is insufficient for the next complete route assessment. "
                + "Remaining places are deferred.";
    }

    private Object await(CompletableFuture<Object> result) {
        try {
            return result.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.getCause() instanceof Error error) {
                throw error;
            }
            throw new ProviderUnavailableException("EXPLORE provider operation failed.");
        }
    }

    public int parallelism() {
        return Math.max(1, Math.min(3, parallelism));
    }

    /** Runs independent candidate checks side by side while all of them share this request's one allowance. */
    public <T, R> List<R> parallel(List<T> values, Function<T, R> work) {
        if (values.size() <= 1) {
            return values.stream().map(work).toList();
        }
        var scope = scopes.get();
        try (var executor = Executors.newFixedThreadPool(Math.min(parallelism(), values.size()))) {
            var tasks = values.stream().map(value -> executor.submit(() -> {
                scopes.set(scope);
                try {
                    return work.apply(value);
                } finally {
                    scopes.remove();
                }
            })).toList();
            List<R> results = new ArrayList<>();
            for (var task : tasks) {
                results.add(finished(task));
            }
            return List.copyOf(results);
        }
    }

    private <R> R finished(java.util.concurrent.Future<R> task) {
        try {
            return task.get();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ProviderUnavailableException("EXPLORE evaluation interrupted; no complete result is available.");
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ProviderUnavailableException("EXPLORE candidate evaluation failed.");
        }
    }
}
