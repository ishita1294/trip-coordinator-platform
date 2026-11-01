package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/** V2 envelope for the existing immutable extracted_data store; not a confirmed reservation. */
public record SemanticTourExtraction(int schemaVersion, Provenance provenance, List<Booking> bookings) {
    public static final int SCHEMA_VERSION = 2;
    public static final String PROMPT_VERSION = "tour-semantic-2.0";
    public SemanticTourExtraction { bookings = bookings == null ? null : List.copyOf(bookings); }

    /** Assigned by our adapter, not returned by the model. Document/extraction IDs remain the DB FK/PK. */
    public record Provenance(String sourceSha256, String model, String promptVersion,
                             String providerResponseId, Instant extractedAt) {}

    public record Booking(int bookingIndex,
            SemanticFact<String> name, SemanticFact<String> provider, SemanticFact<String> bookingReference,
            SemanticFact<LocalMoment> start, SemanticFact<LocalMoment> end, SemanticFact<String> timezone,
            SemanticFact<Integer> durationMinutes, SemanticFact<LocalMoment> requiredArrival,
            SemanticFact<Integer> arrivalLeadMinutes, SemanticFact<DocumentLocation> meetingPoint,
            SemanticFact<EndingLocation> endingPoint, SemanticFact<String> instructions,
            List<SemanticFact<Condition>> conditions) {
        public Booking { conditions = conditions == null ? null : List.copyOf(conditions); }
    }

    /** Partial documented dates/times are retained as unresolved alternatives, never invented timestamps. */
    public record LocalMoment(LocalDate date, LocalTime time) {
        public static LocalMoment of(LocalDateTime value) { return new LocalMoment(value.toLocalDate(), value.toLocalTime()); }
        public LocalDateTime toLocalDateTime() { return LocalDateTime.of(date, time); }
    }

    /** Printed coordinates only; contains no Place ID, provider observation or route-ready flag. */
    public record DocumentLocation(String name, String address, String entrance, String instructions,
                                   Double latitude, Double longitude) {}
    public enum EndingMeaning { SAME_AS_MEETING_POINT, DIFFERENT_LOCATION, UNKNOWN }
    public record EndingLocation(EndingMeaning meaning, DocumentLocation location) {}

    public enum ConditionKind { CANCELLATION, PARTICIPATION, BOOKING_REQUIREMENT }
    public enum Obligation { REQUIRED, PROHIBITED, PERMITTED, CONDITIONAL }
    /** Semantic description is retained even when no numeric restriction is established. No enforcement here. */
    public record Condition(ConditionKind kind, Obligation obligation, String description,
                            Integer minimumAgeYears, Integer noticeMinutes) {}

    /** Only this untrusted portion is accepted from the model. */
    record ModelOutput(int schemaVersion, List<Booking> bookings) {}
}
