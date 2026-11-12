package com.ishita.tripcoordinatorplatform.ai.document;

import static com.ishita.tripcoordinatorplatform.ai.document.TourBookingFacts.EndingLocation;
import static com.ishita.tripcoordinatorplatform.ai.document.TourBookingFacts.EndingMeaning;

import com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Status;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a freshly generated CLEAR ending whose meaning and location disagree into an AMBIGUOUS ending that
 * offers each consistent reading, so the existing ending clarification decides. It never picks a reading,
 * runs only on new model output (stored extractions stay strictly validated) and leaves every other defect
 * for the validator to reject.
 */
final class SemanticEndingReconciler {
    static final String UNCERTAINTY = "Ending meaning and location were inconsistent; clarification needed";

    record Change(int bookingIndex, EndingMeaning meaning, boolean hadLocation) {}
    record Result(List<SemanticTourExtractionV3.Booking> bookings, List<Change> changes) {}

    private SemanticEndingReconciler() {}

    static Result reconcile(List<SemanticTourExtractionV3.Booking> bookings) {
        if (bookings == null) return new Result(null, List.of());
        var reconciled = new ArrayList<SemanticTourExtractionV3.Booking>();
        var changes = new ArrayList<Change>();
        for (var booking : bookings) {
            var ending = booking == null ? null : reconcile(booking);
            if (ending == null) {
                reconciled.add(booking);
                continue;
            }
            var value = booking.endingPoint().value();
            changes.add(new Change(booking.bookingIndex(), value.meaning(), value.location() != null));
            reconciled.add(booking.withEndingPoint(ending));
        }
        return new Result(reconciled, changes);
    }

    /** Null when the ending is consistent or any other rule is broken (the validator must then decide). */
    private static SemanticFactV3<EndingLocation> reconcile(SemanticTourExtractionV3.Booking booking) {
        var fact = booking.endingPoint();
        if (fact == null || fact.status() != Status.CLEAR || fact.value() == null || fact.value().meaning() == null
                || fact.evidence() == null || fact.evidence().isEmpty()
                || fact.alternatives() == null || !fact.alternatives().isEmpty()) return null;
        var value = fact.value();
        boolean different = value.meaning() == EndingMeaning.DIFFERENT_LOCATION;
        if (different ? value.location() != null : value.location() == null) return null;
        return new SemanticFactV3<>(Status.AMBIGUOUS, null, alternatives(booking, value), fact.evidence(),
                UNCERTAINTY, fact.sourceExpressions(), fact.interpretation());
    }

    private static List<EndingLocation> alternatives(SemanticTourExtractionV3.Booking booking, EndingLocation value) {
        var alternatives = new ArrayList<EndingLocation>();
        if (value.location() == null) return alternatives;
        // The model often repeats the meeting-point address; keep that reading when it is a valid one.
        if (value.meaning() == EndingMeaning.SAME_AS_MEETING_POINT && booking.meetingPoint() != null
                && booking.meetingPoint().status() == Status.CLEAR)
            alternatives.add(new EndingLocation(EndingMeaning.SAME_AS_MEETING_POINT, null));
        alternatives.add(new EndingLocation(EndingMeaning.DIFFERENT_LOCATION, value.location()));
        return alternatives;
    }
}
