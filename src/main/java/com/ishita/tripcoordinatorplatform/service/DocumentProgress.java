package com.ishita.tripcoordinatorplatform.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Remembers, for each document being processed, which step the worker has reached ("Reading your document", "Found 3 bookings",
 * "Reading booking 2 of 3", "Checking the details", "Saving"), so the documents screen can show real progress instead of a
 * generic "Processing...".
 * <p>
 * Only a step name and two small counts are stored: never any text from the document. A step is shown only while it belongs to
 * the processing attempt that is currently running, so a retried or finished attempt can never leak an old step. It lives in this
 * server's memory and disappears when processing ends or after a few minutes. That is enough while the API runs the worker itself
 * (local processing mode). When the Lambda worker does the processing, it runs in a separate process that cannot report here, so
 * the screen simply keeps its generic text.
 */
@Component
public class DocumentProgress {

    /** The steps shown to the user. Each covers the whole period until the next one is reported. */
    public enum Stage { READING, FOUND, READING_BOOKINGS, CHECKING, SAVING }

    /** How long an unfinished entry is kept. */
    static final Duration LIFETIME = Duration.ofMinutes(10);
    /** A safety limit so abandoned entries can never grow without bound. */
    static final int MAX_ENTRIES = 2000;

    private record Entry(UUID attemptId, Stage stage, int found, int done, Instant updated) { }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    private final Clock clock;

    public DocumentProgress() {
        this(Clock.systemUTC());
    }

    DocumentProgress(Clock clock) {
        this.clock = clock;
    }

    /** Records the step a document has reached, replacing the previous one. {@code found} and {@code done} are booking counts. */
    public void report(Long documentId, UUID attemptId, Stage stage, int found, int done) {
        if (documentId == null || attemptId == null) return;
        Instant now = clock.instant();
        if (entries.size() >= MAX_ENTRIES) entries.values().removeIf(entry -> expired(entry, now));
        if (entries.size() >= MAX_ENTRIES && !entries.containsKey(documentId)) return;
        entries.put(documentId, new Entry(attemptId, stage, found, done, now));
    }

    /** Shorthand for a step that needs no counts. */
    public void report(Long documentId, UUID attemptId, Stage stage) {
        report(documentId, attemptId, stage, 0, 0);
    }

    /** Returns the wording for the given attempt only, and only while the entry is still fresh. */
    public Optional<String> text(Long documentId, UUID currentAttemptId) {
        var entry = documentId == null ? null : entries.get(documentId);
        if (entry == null || currentAttemptId == null) return Optional.empty();
        if (expired(entry, clock.instant())) {
            entries.remove(documentId, entry);
            return Optional.empty();
        }
        return entry.attemptId().equals(currentAttemptId) ? Optional.of(wording(entry)) : Optional.empty();
    }

    /** Forgets a document's step once its processing attempt has ended, but only if no newer attempt has reported meanwhile. */
    public void clear(Long documentId, UUID attemptId) {
        var entry = documentId == null ? null : entries.get(documentId);
        if (entry != null && entry.attemptId().equals(attemptId)) entries.remove(documentId, entry);
    }

    private static String wording(Entry entry) {
        return switch (entry.stage()) {
            case READING -> "Reading your document";
            case FOUND -> "Found " + entry.found() + (entry.found() == 1 ? " booking" : " bookings");
            case READING_BOOKINGS -> "Reading booking " + entry.done() + " of " + entry.found();
            case CHECKING -> "Checking the details";
            case SAVING -> "Saving";
        };
    }

    private boolean expired(Entry entry, Instant now) {
        return entry.updated().plus(LIFETIME).isBefore(now);
    }
}
