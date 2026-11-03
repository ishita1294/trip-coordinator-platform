package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;
import static com.ishita.tripcoordinatorplatform.ai.document.SemanticTourExtraction.*;

/** Staged successor. No production processing, persistence or confirmation route selects v3. */
public record SemanticTourExtractionV3(int schemaVersion, Provenance provenance, List<Booking> bookings,
        List<SemanticSupplementalFactV3> supplementalFacts, String supplementalCoverageWarning) {
    public static final int SCHEMA_VERSION = 3;
    public static final String PROMPT_VERSION = "tour-semantic-3.4";
    public static boolean isV34Contract(String identity) {
        return PROMPT_VERSION.equals(identity);
    }
    public static final String STRUCTURED_OUTPUT_NAME = "tour_semantic_v3_4";
    /** The only contract this application reads. */
    public static final java.util.Set<String> READABLE_PROMPT_VERSIONS = java.util.Set.of(PROMPT_VERSION);
    public SemanticTourExtractionV3 {
        bookings = bookings == null ? null : List.copyOf(bookings);
        supplementalFacts = supplementalFacts == null ? null : List.copyOf(supplementalFacts);
    }
    /** Booking-local scope is the containing bookingIndex; shared/uncertain scope belongs in supplementalFacts. */
    public record Condition(ConditionKind kind, SemanticAssertionV3 semantics, Integer minimumAgeYears, Integer noticeMinutes) {
        SemanticTourExtraction.Condition shape() {
            return new SemanticTourExtraction.Condition(kind,
                    switch (semantics.modality()) {
                        case REQUIRED -> Obligation.REQUIRED;
                        case PROHIBITED -> Obligation.PROHIBITED;
                        case PERMITTED -> Obligation.PERMITTED;
                        case NOT_APPLICABLE -> Obligation.CONDITIONAL;
                    }, semantics.description(), minimumAgeYears, noticeMinutes);
        }
    }
    public record Booking(int bookingIndex,
            SemanticFactV3<String> name, SemanticFactV3<String> provider, SemanticFactV3<String> bookingReference,
            SemanticFactV3<LocalMoment> start, SemanticFactV3<LocalMoment> end, SemanticFactV3<String> timezone,
            SemanticFactV3<Integer> durationMinutes, SemanticFactV3<LocalMoment> requiredArrival,
            SemanticFactV3<Integer> arrivalLeadMinutes, SemanticFactV3<DocumentLocation> meetingPoint,
            SemanticFactV3<EndingLocation> endingPoint, SemanticFactV3<String> instructions,
            List<SemanticFactV3<Condition>> conditions, List<SemanticTemporalConstraintV3> temporalConstraints,
            SemanticFactV3<SemanticBookingLifecycleV3.State> lifecycle,
            List<SemanticFactV3<SemanticBookingLifecycleV3.BookingAssociation>> replaces) {
        public Booking {
            conditions = conditions == null ? null : List.copyOf(conditions);
            temporalConstraints = temporalConstraints == null ? null : List.copyOf(temporalConstraints);
            replaces = replaces == null ? null : List.copyOf(replaces);
        }
        /** Historical callers have no documentary lifecycle information; never default ACTIVE. */
        public Booking(int bookingIndex, SemanticFactV3<String> name, SemanticFactV3<String> provider,
                SemanticFactV3<String> bookingReference, SemanticFactV3<LocalMoment> start,
                SemanticFactV3<LocalMoment> end, SemanticFactV3<String> timezone,
                SemanticFactV3<Integer> durationMinutes, SemanticFactV3<LocalMoment> requiredArrival,
                SemanticFactV3<Integer> arrivalLeadMinutes, SemanticFactV3<DocumentLocation> meetingPoint,
                SemanticFactV3<EndingLocation> endingPoint, SemanticFactV3<String> instructions,
                List<SemanticFactV3<Condition>> conditions, List<SemanticTemporalConstraintV3> temporalConstraints) {
            this(bookingIndex,name,provider,bookingReference,start,end,timezone,durationMinutes,requiredArrival,
                    arrivalLeadMinutes,meetingPoint,endingPoint,instructions,conditions,temporalConstraints,
                    SemanticBookingLifecycleV3.unestablished(),List.of());
        }
        /** Source compatibility for historical 3.1 and existing synthetic callers. */
        public Booking(int bookingIndex, SemanticFactV3<String> name, SemanticFactV3<String> provider,
                SemanticFactV3<String> bookingReference, SemanticFactV3<LocalMoment> start,
                SemanticFactV3<LocalMoment> end, SemanticFactV3<String> timezone,
                SemanticFactV3<Integer> durationMinutes, SemanticFactV3<LocalMoment> requiredArrival,
                SemanticFactV3<Integer> arrivalLeadMinutes, SemanticFactV3<DocumentLocation> meetingPoint,
                SemanticFactV3<EndingLocation> endingPoint, SemanticFactV3<String> instructions,
                List<SemanticFactV3<Condition>> conditions) {
            this(bookingIndex,name,provider,bookingReference,start,end,timezone,durationMinutes,requiredArrival,
                    arrivalLeadMinutes,meetingPoint,endingPoint,instructions,conditions,List.of());
        }
        Booking withEndingPoint(SemanticFactV3<EndingLocation> ending) {
            return new Booking(bookingIndex, name, provider, bookingReference, start, end, timezone, durationMinutes,
                    requiredArrival, arrivalLeadMinutes, meetingPoint, ending, instructions, conditions,
                    temporalConstraints, lifecycle, replaces);
        }
        SemanticTourExtraction.Booking shape() {
            return new SemanticTourExtraction.Booking(bookingIndex, name.shape(), provider.shape(), bookingReference.shape(),
                    start.shape(), end.shape(), timezone.shape(), durationMinutes.shape(), requiredArrival.shape(),
                    arrivalLeadMinutes.shape(), meetingPoint.shape(), endingPoint.shape(), instructions.shape(),
                    conditions.stream().map(f -> f.shape(Condition::shape)).toList());
        }
    }
    record ModelOutput(int schemaVersion, List<Booking> bookings,
            List<SemanticSupplementalFactV3> supplementalFacts, String supplementalCoverageWarning) {}
}
