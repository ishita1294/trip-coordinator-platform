package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;
import static com.ishita.tripcoordinatorplatform.ai.document.TourBookingFacts.*;

/** Staged successor. No production processing, persistence or confirmation route selects v3. */
public record TourReading(int schemaVersion, Provenance provenance, List<Booking> bookings,
        List<SupplementalReadingFact> supplementalFacts, String supplementalCoverageWarning) {
    public static final int SCHEMA_VERSION = 3;
    public static final String PROMPT_VERSION = "tour-semantic-3.4";
    public static boolean isCurrentContract(String identity) {
        return PROMPT_VERSION.equals(identity);
    }
    public static final String STRUCTURED_OUTPUT_NAME = "tour_semantic_v3_4";
    /** The only contract this application reads. */
    public static final java.util.Set<String> READABLE_PROMPT_VERSIONS = java.util.Set.of(PROMPT_VERSION);
    public TourReading {
        bookings = bookings == null ? null : List.copyOf(bookings);
        supplementalFacts = supplementalFacts == null ? null : List.copyOf(supplementalFacts);
    }
    /** Booking-local scope is the containing bookingIndex; shared/uncertain scope belongs in supplementalFacts. */
    public record Condition(ConditionKind kind, ReadingAssertion semantics, Integer minimumAgeYears, Integer noticeMinutes) {
        TourBookingFacts.Condition shape() {
            return new TourBookingFacts.Condition(kind,
                    switch (semantics.modality()) {
                        case REQUIRED -> Obligation.REQUIRED;
                        case PROHIBITED -> Obligation.PROHIBITED;
                        case PERMITTED -> Obligation.PERMITTED;
                        case NOT_APPLICABLE -> Obligation.CONDITIONAL;
                    }, semantics.description(), minimumAgeYears, noticeMinutes);
        }
    }
    public record Booking(int bookingIndex,
            ReadingFact<String> name, ReadingFact<String> provider, ReadingFact<String> bookingReference,
            ReadingFact<LocalMoment> start, ReadingFact<LocalMoment> end, ReadingFact<String> timezone,
            ReadingFact<Integer> durationMinutes, ReadingFact<LocalMoment> requiredArrival,
            ReadingFact<Integer> arrivalLeadMinutes, ReadingFact<DocumentLocation> meetingPoint,
            ReadingFact<EndingLocation> endingPoint, ReadingFact<String> instructions,
            List<ReadingFact<Condition>> conditions, List<ReadingTimeRule> temporalConstraints,
            ReadingFact<ReadingBookingLifecycle.State> lifecycle,
            List<ReadingFact<ReadingBookingLifecycle.BookingAssociation>> replaces) {
        public Booking {
            conditions = conditions == null ? null : List.copyOf(conditions);
            temporalConstraints = temporalConstraints == null ? null : List.copyOf(temporalConstraints);
            replaces = replaces == null ? null : List.copyOf(replaces);
        }
        /** Historical callers have no documentary lifecycle information; never default ACTIVE. */
        public Booking(int bookingIndex, ReadingFact<String> name, ReadingFact<String> provider,
                ReadingFact<String> bookingReference, ReadingFact<LocalMoment> start,
                ReadingFact<LocalMoment> end, ReadingFact<String> timezone,
                ReadingFact<Integer> durationMinutes, ReadingFact<LocalMoment> requiredArrival,
                ReadingFact<Integer> arrivalLeadMinutes, ReadingFact<DocumentLocation> meetingPoint,
                ReadingFact<EndingLocation> endingPoint, ReadingFact<String> instructions,
                List<ReadingFact<Condition>> conditions, List<ReadingTimeRule> temporalConstraints) {
            this(bookingIndex,name,provider,bookingReference,start,end,timezone,durationMinutes,requiredArrival,
                    arrivalLeadMinutes,meetingPoint,endingPoint,instructions,conditions,temporalConstraints,
                    ReadingBookingLifecycle.unestablished(),List.of());
        }
        /** Shorter form for callers that do not supply the lifecycle or arrival-lead details. */
        public Booking(int bookingIndex, ReadingFact<String> name, ReadingFact<String> provider,
                ReadingFact<String> bookingReference, ReadingFact<LocalMoment> start,
                ReadingFact<LocalMoment> end, ReadingFact<String> timezone,
                ReadingFact<Integer> durationMinutes, ReadingFact<LocalMoment> requiredArrival,
                ReadingFact<Integer> arrivalLeadMinutes, ReadingFact<DocumentLocation> meetingPoint,
                ReadingFact<EndingLocation> endingPoint, ReadingFact<String> instructions,
                List<ReadingFact<Condition>> conditions) {
            this(bookingIndex,name,provider,bookingReference,start,end,timezone,durationMinutes,requiredArrival,
                    arrivalLeadMinutes,meetingPoint,endingPoint,instructions,conditions,List.of());
        }
        Booking withEndingPoint(ReadingFact<EndingLocation> ending) {
            return new Booking(bookingIndex, name, provider, bookingReference, start, end, timezone, durationMinutes,
                    requiredArrival, arrivalLeadMinutes, meetingPoint, ending, instructions, conditions,
                    temporalConstraints, lifecycle, replaces);
        }
        TourBookingFacts.Booking shape() {
            return new TourBookingFacts.Booking(bookingIndex, name.shape(), provider.shape(), bookingReference.shape(),
                    start.shape(), end.shape(), timezone.shape(), durationMinutes.shape(), requiredArrival.shape(),
                    arrivalLeadMinutes.shape(), meetingPoint.shape(), endingPoint.shape(), instructions.shape(),
                    conditions.stream().map(f -> f.shape(Condition::shape)).toList());
        }
    }
    record ModelOutput(int schemaVersion, List<Booking> bookings,
            List<SupplementalReadingFact> supplementalFacts, String supplementalCoverageWarning) {}
}
