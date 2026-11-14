package com.ishita.tripcoordinatorplatform.ai.document;

import com.openai.core.RequestOptions;
import com.openai.core.Timeout;
import java.io.InterruptedIOException;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.function.IntSupplier;

/** Bounds document requests and leaves time to save the outcome before Lambda stops execution. */
public final class DocumentProcessingBudget implements AutoCloseable {
    // Invocation limits and leases must match the API and Lambda release configuration.
    public static final int LAMBDA_SECONDS = 180;
    public static final int LEASE_SECONDS = 240;
    public static final int PERSISTENCE_RESERVE_SECONDS = 30;
    private static final ThreadLocal<IntSupplier> REMAINING = new ThreadLocal<>();
    private final IntSupplier previous;

    /** Environment setting for the extraction request timeout; unset keeps the Lambda-sized default. */
    public static final String EXTRACTION_TIMEOUT_ENV = "DOCUMENT_PROCESSING_EXTRACTION_TIMEOUT_SECONDS";
    static final int DEFAULT_EXTRACTION_SECONDS = 90;
    static final int MIN_EXTRACTION_SECONDS = 30;
    static final int MAX_EXTRACTION_SECONDS = LEASE_SECONDS;

    /** The request limit for each provider stage, in seconds. Classification gets 30 so a slow reply is not mistaken for a failure. */
    public enum Stage {
        CLASSIFICATION(30), EXTRACTION(extractionSeconds(System.getenv(EXTRACTION_TIMEOUT_ENV)));
        private final int seconds;
        Stage(int seconds) { this.seconds = seconds; }
    }

    /** Parses the extraction timeout; a value outside 30 seconds to the lease length is refused, never clamped. */
    static int extractionSeconds(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_EXTRACTION_SECONDS;
        try {
            int seconds = Integer.parseInt(raw.trim());
            if (seconds >= MIN_EXTRACTION_SECONDS && seconds <= MAX_EXTRACTION_SECONDS) return seconds;
        } catch (NumberFormatException ignored) {
            // reported below without echoing the value
        }
        throw new IllegalStateException(EXTRACTION_TIMEOUT_ENV + " must be " + MIN_EXTRACTION_SECONDS + " to "
                + MAX_EXTRACTION_SECONDS + " seconds");
    }

    private DocumentProcessingBudget(IntSupplier remaining) {
        previous = REMAINING.get();
        if (remaining == null) REMAINING.remove(); else REMAINING.set(remaining);
    }

    /** Uses Lambda's live remaining time, including time already spent starting Spring. */
    public static DocumentProcessingBudget forInvocation(IntSupplier remaining) {
        return new DocumentProcessingBudget(remaining);
    }

    /**
     * Binds the caller's current remaining-time supplier (if any) to the task, whichever thread runs it. The budget is
     * thread-local, so a task handed to an executor would otherwise see no invocation deadline. The supplier is read
     * live when each request starts, and the worker thread's own scope is restored afterwards.
     */
    public static <T> Callable<T> propagate(Callable<T> task) {
        IntSupplier captured = REMAINING.get();
        return () -> {
            try (var ignored = forInvocation(captured)) {
                return task.call();
            }
        };
    }

    /** Time a provider call could still use after keeping the persistence reserve; unbounded outside an invocation. */
    public static long usableExtractionMillis() {
        var remaining = REMAINING.get();
        return remaining == null ? Long.MAX_VALUE : (long) remaining.getAsInt() - PERSISTENCE_RESERVE_SECONDS * 1000L;
    }

    /**
     * Longest a call started now can keep running: the extraction cap, clipped to the usable invocation time. A caller
     * that waits for in-flight calls to end can bound its wait by this, because each call's own timeout is no longer.
     */
    public static long extractionJoinMillis() {
        return Math.max(0, Math.min(Stage.EXTRACTION.seconds * 1000L, usableExtractionMillis()));
    }

    public static RequestOptions requestOptions(Stage stage) {
        long millis = stage.seconds * 1000L;
        var remaining = REMAINING.get();
        if (remaining != null) millis = Math.min(millis,
                (long) remaining.getAsInt() - PERSISTENCE_RESERVE_SECONDS * 1000L);
        if (millis <= 0) throw new ProviderTimeout(stage, "INVOCATION_BUDGET_EXHAUSTED", null);
        return RequestOptions.builder().timeout(timeout(millis)).build();
    }

    /** A complete HTTP call includes connection, upload and response consumption; SDK retries remain zero. */
    public static Timeout clientTimeout(Stage stage) { return timeout(stage.seconds * 1000L); }

    private static Timeout timeout(long millis) {
        return Timeout.builder().connect(Duration.ofMillis(Math.min(5000, millis)))
                .write(Duration.ofMillis(Math.min(10000, millis)))
                .read(Duration.ofMillis(millis)).request(Duration.ofMillis(millis)).build();
    }

    /** Leave room for finalization and a separate failure transaction if finalization rolls back. */
    public static void requireFinalizationTime() {
        requirePersistenceTime("FINALIZATION", PERSISTENCE_RESERVE_SECONDS);
    }

    /** Pool acquisition can take five seconds before the ten-second failure transaction starts. */
    public static void requireFailurePersistenceTime() {
        requirePersistenceTime("FAILURE_PERSISTENCE", 15);
    }

    private static void requirePersistenceTime(String phase, int seconds) {
        var remaining = REMAINING.get();
        if (remaining != null && remaining.getAsInt() < seconds * 1000)
            throw new PersistenceBudgetExhausted(phase);
    }

    public static boolean isTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof ProviderTimeout || cause instanceof PersistenceBudgetExhausted || cause instanceof InterruptedIOException
                    || cause instanceof HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException)
                return true;
            if (cause == cause.getCause()) break;
        }
        return false;
    }

    public static RuntimeException providerFailure(Stage stage, RuntimeException failure) {
        if (failure instanceof ProviderTimeout) return failure;
        return isTimeout(failure) ? new ProviderTimeout(stage, "PROVIDER_TIMEOUT", failure) : failure;
    }

    @Override public void close() {
        if (previous == null) REMAINING.remove(); else REMAINING.set(previous);
    }

    /** No database write is started when the invocation cannot allow its bounded persistence work. */
    public static final class PersistenceBudgetExhausted extends IllegalStateException {
        private final String phase;
        private PersistenceBudgetExhausted(String phase) {
            super("INVOCATION_BUDGET_EXHAUSTED: " + phase);
            this.phase = phase;
        }
        public String phase() { return phase; }
    }

    /** Contains only a stage and reason; provider response contents are never used as the message. */
    public static final class ProviderTimeout extends IllegalStateException {
        private final Stage stage;
        private final String reason;
        private ProviderTimeout(Stage stage, String reason, Throwable cause) {
            super(reason + ": " + stage, cause); this.stage = stage; this.reason = reason;
        }
        public Stage stage() { return stage; }
        public String reason() { return reason; }
    }
}
