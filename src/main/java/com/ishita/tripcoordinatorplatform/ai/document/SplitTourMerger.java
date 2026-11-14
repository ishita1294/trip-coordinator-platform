package com.ishita.tripcoordinatorplatform.ai.document;

import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Basis;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Status;

/**
 * Combines the outcomes of a split extraction into one ordinary V3.4 extraction. A booking whose content was unusable
 * becomes an unresolved stub; anything that cannot be judged booking by booking (duplicates, replacement links,
 * lifecycle, supplemental scope, size limits) fails the merged document with a {@code split-fallback} reason, so the
 * job's retry, which has no inventory, runs the single call instead. Nothing is repaired.
 *
 * <p>One exception to "nothing is repaired": the supplemental facts of the separate calls can together be larger than
 * the document allows. They are then fitted by {@link SplitSupplementalBudget} (merge repeats, then drop the least
 * valuable facts with a visible warning) rather than sending a large document to the slow single call.
 */
public final class SplitTourMerger {
    static final String FALLBACK = "split-fallback: ";
    static final int MAX_WARNING = 500;
    static final String UNLISTED_MARKER = "UNLISTED BOOKING:";
    private static final int MAX_REASON = 160;

    public record Merged(TourReading extraction, List<Integer> unresolvedOrdinals, boolean sharedFactsMissing) {}

    private SplitTourMerger() {}

    public static Merged merge(SplitSource source, String model, BookingInventory inventory,
            List<SplitOutcome> bookingOutcomes, SplitOutcome shared) {
        requireComplete(inventory, bookingOutcomes, shared);
        requireNoUnlistedBooking(bookingOutcomes, shared);
        if (bookingOutcomes.stream().noneMatch(SplitOutcome::ok)) throw fallback("every booking extraction was unusable");
        var bookings = new ArrayList<TourReading.Booking>();
        var unresolved = new ArrayList<Integer>();
        for (var outcome : bookingOutcomes) {
            int ordinal = outcome.target().ordinal();
            if (outcome.ok()) bookings.add(outcome.result().bookings().getFirst());
            else {
                bookings.add(stub(inventory.bookings().get(ordinal), outcome.failureReason()));
                unresolved.add(ordinal);
            }
        }
        requireDistinctAndConsistent(inventory, bookings, unresolved);
        var facts = new ArrayList<>(shared.ok() ? shared.result().supplementalFacts() : List.<SupplementalReadingFact>of());
        bookingOutcomes.stream().filter(SplitOutcome::ok).forEach(o -> facts.addAll(o.result().supplementalFacts()));
        var fitted = SplitSupplementalBudget.fit(facts, inventory);
        var provenance = new TourBookingFacts.Provenance(source.sha256(), model,
                TourReading.PROMPT_VERSION,
                responseIds(bookingOutcomes, shared), Instant.now());
        var merged = new TourReading(TourReading.SCHEMA_VERSION, provenance, bookings, fitted.facts(),
                withTrimNotice(warning(bookingOutcomes, shared, unresolved), fitted.trimmed()));
        try {
            new TourReadingValidator().validate(merged, source.sha256());
        } catch (InvalidDocumentExtractionException invalid) {
            throw fallback("merged document failed validation: " + invalid.getMessage());
        }
        logSummary(bookingOutcomes, shared, unresolved);
        return new Merged(merged, List.copyOf(unresolved), !shared.ok());
    }

    /** When facts were dropped to fit the size limit, say so first in the coverage warning so a reviewer always sees it. */
    private static String withTrimNotice(String warning, int trimmed) {
        return trimmed == 0 ? warning : SplitSupplementalBudget.trimmedWarning(trimmed, warning, MAX_WARNING);
    }

    private static void requireComplete(BookingInventory inventory, List<SplitOutcome> outcomes, SplitOutcome shared) {
        boolean ordered = outcomes.size() == inventory.count();
        for (int i = 0; ordered && i < outcomes.size(); i++) {
            var target = outcomes.get(i).target();
            ordered = target.kind() == SplitCallTarget.Kind.BOOKING && target.ordinal() == i;
        }
        if (!ordered || shared == null || shared.target().kind() != SplitCallTarget.Kind.SHARED)
            throw new IllegalArgumentException("Split outcomes must be one per inventory booking in order, plus the shared call");
    }

    /** A call reported a documented booking that the inventory lacks: the list is incomplete, so no split result can be trusted. */
    private static void requireNoUnlistedBooking(List<SplitOutcome> outcomes, SplitOutcome shared) {
        var all = new ArrayList<>(outcomes);
        all.add(shared);
        for (var outcome : all) {
            if (!outcome.ok() || outcome.result().coverageWarning() == null) continue;
            if (outcome.result().coverageWarning().strip().regionMatches(true, 0, UNLISTED_MARKER, 0, UNLISTED_MARKER.length()))
                throw fallback("a call reported a booking missing from the inventory");
        }
    }

    /** Two calls that found the same booking, or one that found another booking than the inventory named. */
    private static void requireDistinctAndConsistent(BookingInventory inventory,
            List<TourReading.Booking> bookings, List<Integer> unresolved) {
        var references = new HashSet<String>();
        var identities = new HashSet<List<String>>();
        for (var booking : bookings) {
            if (unresolved.contains(booking.bookingIndex())) continue;
            var reference = booking.bookingReference().status() == Status.CLEAR ? normalize(booking.bookingReference().value()) : null;
            if (reference != null && !references.add(reference))
                throw fallback("two bookings carry the same booking reference");
            if (booking.name().status() == Status.CLEAR && booking.start().status() == Status.CLEAR
                    && !identities.add(List.of(normalize(booking.name().value()), booking.start().value().toString())))
                throw fallback("two bookings have the same name and start");
            var hint = normalize(inventory.bookings().get(booking.bookingIndex()).reference());
            if (hint != null && reference != null && !hint.contains(reference) && !reference.contains(hint))
                throw fallback("booking " + booking.bookingIndex() + " reference differs from the inventory");
        }
    }

    private static String normalize(String text) {
        if (text == null) return null;
        String reduced = text.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
        return reduced.isEmpty() ? null : reduced;
    }

    private static String responseIds(List<SplitOutcome> outcomes, SplitOutcome shared) {
        var ids = outcomes.stream().map(o -> o.ok() ? o.result().providerResponseId() : "unresolved")
                .collect(Collectors.joining(","));
        return "split:" + ids + ",shared:" + (shared.ok() ? shared.result().providerResponseId() : "unresolved");
    }

    private static String warning(List<SplitOutcome> outcomes, SplitOutcome shared, List<Integer> unresolved) {
        var parts = new ArrayList<String>();
        unresolved.forEach(i -> parts.add("Booking " + i + " could not be extracted automatically."));
        if (!shared.ok()) parts.add("Shared and document-level facts could not be extracted automatically.");
        else if (shared.result().coverageWarning() != null) parts.add("Shared: " + shared.result().coverageWarning());
        for (var outcome : outcomes)
            if (outcome.ok() && outcome.result().coverageWarning() != null)
                parts.add("Booking " + outcome.target().ordinal() + ": " + outcome.result().coverageWarning());
        if (parts.isEmpty()) return null;
        String joined = String.join(" ", parts);
        return joined.length() <= MAX_WARNING ? joined : joined.substring(0, MAX_WARNING - 1) + "…";
    }

    /** Every fact MISSING, no conditions, no relationships. The label is only an unverified AI hint in the text. */
    static TourReading.Booking stub(BookingInventory.Entry entry, String reason) {
        String why = "Booking " + entry.ordinal() + " could not be extracted automatically (" + shorten(reason) + ").";
        String hint = why + " Document list hint, AI-derived and unverified: " + shorten(entry.label()) + ".";
        return new TourReading.Booking(entry.ordinal(), missing(hint), missing(why), missing(why), missing(why),
                missing(why), missing(why), missing(why), missing(why), missing(why), missing(why), missing(why), missing(why),
                List.of(), List.of(), missing(why), List.of());
    }

    private static <T> ReadingFact<T> missing(String uncertainty) {
        return new ReadingFact<>(Status.MISSING, null, List.of(), List.of(), uncertainty, List.of(),
                new ReadingFact.Interpretation(Basis.NOT_ESTABLISHED, List.of(), null));
    }

    private static String shorten(String text) {
        String clean = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return clean.length() <= MAX_REASON ? clean : clean.substring(0, MAX_REASON - 1) + "…";
    }

    private static InvalidDocumentExtractionException fallback(String reason) {
        LoggerFactory.getLogger(SplitTourMerger.class).warn("Document split-fallback reason={}", reason);
        return new InvalidDocumentExtractionException(FALLBACK + reason);
    }

    private static void logSummary(List<SplitOutcome> outcomes, SplitOutcome shared, List<Integer> unresolved) {
        var all = new ArrayList<>(outcomes);
        all.add(shared);
        var stats = all.stream().filter(SplitOutcome::ok).map(o -> o.result().stats()).toList();
        LoggerFactory.getLogger(SplitTourMerger.class).info(
                "Document split summary calls={} succeeded={} unresolved={} sharedOk={} wallMsAtLeast={} sumCallMs={} "
                        + "inputTokens={} cachedInputTokens={} outputTokens={} reasoningTokens={}", all.size(), stats.size(),
                unresolved, shared.ok(), stats.stream().mapToLong(CallStats::elapsedMs).max().orElse(0),
                stats.stream().mapToLong(CallStats::elapsedMs).sum(), sum(stats, CallStats::inputTokens),
                sum(stats, CallStats::cachedInputTokens), sum(stats, CallStats::outputTokens),
                sum(stats, CallStats::reasoningTokens));
    }

    private static Long sum(List<CallStats> stats, java.util.function.Function<CallStats, Long> field) {
        return stats.stream().map(field).filter(java.util.Objects::nonNull).mapToLong(Long::longValue).sum();
    }
}
