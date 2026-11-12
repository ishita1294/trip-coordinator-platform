package com.ishita.tripcoordinatorplatform.ai.document;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import static com.ishita.tripcoordinatorplatform.ai.document.BookingFact.Status.CLEAR;

/** Documentary local-time semantics, not a rule engine or a verified scheduling instant. */
public record SemanticTemporalConstraintV3(Target target, SemanticFactV3<Requirement> fact) {
    public enum Target { START, END, REQUIRED_ARRIVAL }
    public enum Relation { EXACT, DEADLINE, EARLIEST, WINDOW }
    public record Boundary(TourBookingFacts.LocalMoment moment, Boolean inclusive) {}
    public record Requirement(Relation relation, Boundary lower, Boundary upper,
            SemanticAssertionV3.Conditionality conditionality, String trigger) {
        /** Local-domain membership only. Does not resolve timezone, offsets or DST. */
        public boolean permits(LocalDateTime candidate) {
            if (conditionality != SemanticAssertionV3.Conditionality.UNCONDITIONAL)
                throw new IllegalStateException("Conditional temporal requirement needs explicit handling");
            return (lower == null || candidate.isAfter(lower.moment().toLocalDateTime())
                    || lower.inclusive() && candidate.equals(lower.moment().toLocalDateTime()))
                    && (upper == null || candidate.isBefore(upper.moment().toLocalDateTime())
                    || upper.inclusive() && candidate.equals(upper.moment().toLocalDateTime()));
        }
    }
    static void validate(SemanticTourExtractionV3.Booking booking, SemanticTourExtractionV3Validator validator,
            Map<SemanticFactV3.Reference, SemanticFactV3<?>> fields) {
        require(booking.temporalConstraints() != null && booking.temporalConstraints().size() <= 12,
                "Temporal constraints are required and bounded to 12 per booking");
        var targets = new java.util.HashSet<Target>();
        for (var c : booking.temporalConstraints()) {
            require(c != null && c.target() != null && targets.add(c.target()), "Duplicate or missing temporal target");
            validator.validateFact(c.fact(), fields);
            require(c.fact().interpretation().calculation() == null,
                    "Temporal boundaries reference calculated typed facts; constraints are not arithmetic results");
            for (var e : c.fact().evidence()) require(e.bookingIndex() != null && e.bookingIndex() == booking.bookingIndex(),
                    "Temporal evidence booking association is invalid");
            if (c.fact().value() != null) validateRequirement(c.fact().value(), c.fact().status() == CLEAR);
            c.fact().alternatives().forEach(r -> validateRequirement(r, false));
            if (c.fact().status() == CLEAR) {
                var scalar = switch (c.target()) {
                    case START -> booking.start(); case END -> booking.end(); case REQUIRED_ARRIVAL -> booking.requiredArrival();
                };
                var r = c.fact().value();
                // Scalar is an exact schedule or the upper deadline boundary, never a lossless window substitute.
                var boundary = r.relation() == Relation.EXACT ? r.lower() : r.relation() == Relation.DEADLINE ? r.upper() : null;
                if (boundary != null && scalar.status() == CLEAR)
                    require(boundary.moment().equals(scalar.value()), "Temporal boundary/scalar conflict");
            }
        }
    }
    private static void validateRequirement(Requirement r, boolean clear) {
        require(r != null && r.relation() != null && r.conditionality() != null, "Incomplete temporal requirement");
        if (r.conditionality() == SemanticAssertionV3.Conditionality.CONDITIONAL)
            require(r.trigger() != null && !r.trigger().isBlank() && r.trigger().length() <= 400, "Temporal condition needs a trigger");
        else require(r.trigger() == null, "Unexpected temporal trigger");
        require(!clear || r.conditionality() != SemanticAssertionV3.Conditionality.UNRESOLVED,
                "Unresolved temporal condition cannot be clear");
        for (var b : new Boundary[]{r.lower(), r.upper()}) if (b != null)
            require(b.moment() != null && b.moment().date() != null && b.moment().time() != null && b.inclusive() != null,
                    "Temporal boundary requires full local date/time and inclusivity");
        switch (r.relation()) {
            case DEADLINE -> require(r.lower() == null && r.upper() != null, "Deadline requires upper boundary only");
            case EARLIEST -> require(r.lower() != null && r.upper() == null, "Earliest requires lower boundary only");
            case EXACT, WINDOW -> {
                require(r.lower() != null && r.upper() != null, "Exact/window requires both boundaries");
                var lo = r.lower().moment().toLocalDateTime(); var hi = r.upper().moment().toLocalDateTime();
                if (r.relation() == Relation.EXACT)
                    require(lo.equals(hi) && r.lower().inclusive() && r.upper().inclusive(), "Exact requires identical inclusive boundaries");
                else require(lo.isBefore(hi), "Window upper boundary must follow lower boundary");
            }
        }
    }
    /** Existing reservation fields support exact start/end and only inclusive mandatory arrival deadlines. */
    public static boolean representable(SemanticTourExtractionV3.Booking b) {
        return b.temporalConstraints().stream().allMatch(c -> c.fact().status() == CLEAR
                && c.fact().value().conditionality() == SemanticAssertionV3.Conditionality.UNCONDITIONAL
                && (c.target() == Target.REQUIRED_ARRIVAL
                    ? c.fact().value().relation() == Relation.DEADLINE && c.fact().value().upper().inclusive()
                    : c.fact().value().relation() == Relation.EXACT));
    }
    public static boolean matchesEffective(SemanticTourExtractionV3.Booking b, LocalDateTime start,
            LocalDateTime end, LocalDateTime arrival) {
        return b.temporalConstraints().stream().allMatch(c -> {
            var r=c.fact().value();
            var expected=c.target()==Target.REQUIRED_ARRIVAL?r.upper().moment():r.lower().moment();
            var actual=switch(c.target()){case START->start;case END->end;case REQUIRED_ARRIVAL->arrival;};
            return expected.toLocalDateTime().equals(actual);
        });
    }
    /** Corrections cannot erase or shift constraints, nor retain constraints derived from changed operands. */
    public static boolean correctionCompatible(SemanticTourExtractionV3.Booking b, Set<String> edits) {
        if (b.temporalConstraints().isEmpty()) return true; // A booking with no recorded time rules has nothing to protect.
        var temporal = Set.of("startDate","startTime","endDate","endTime","timezone","requiredArrivalLocal","arrivalLeadMinutes","durationMinutes");
        if (edits.stream().anyMatch(temporal::contains)) return false;
        return b.temporalConstraints().stream().noneMatch(c -> {
            var refs = new java.util.ArrayList<>(c.fact().interpretation().dependencies());
            refs.add(new SemanticFactV3.Reference(b.bookingIndex(), BookingFact.Field.valueOf(c.target().name()), null));
            return refs.stream().anyMatch(ref -> affected(ref,b,edits,new java.util.HashSet<>()));
        });
    }
    private static boolean affected(SemanticFactV3.Reference ref, SemanticTourExtractionV3.Booking b,
            Set<String> edits, Set<SemanticFactV3.Reference> visited) {
        if (!visited.add(ref)) return false;
        var keys = switch (ref.field()) {
            case START -> Set.of("startDate","startTime"); case END -> Set.of("endDate","endTime");
            case REQUIRED_ARRIVAL -> Set.of("requiredArrivalLocal"); case ARRIVAL_LEAD_MINUTES -> Set.of("arrivalLeadMinutes");
            case DURATION_MINUTES -> Set.of("durationMinutes"); case BOOKING_REFERENCE -> Set.of("bookingReference");
            case MEETING_POINT -> Set.of("meetingPoint"); case ENDING_POINT -> Set.of("endingPoint","endingLocationChoice");
            default -> Set.of(ref.field().name().toLowerCase());
        };
        if (keys.stream().anyMatch(edits::contains)) return true;
        SemanticFactV3<?> f = switch(ref.field()) {
            case START -> b.start(); case END -> b.end(); case REQUIRED_ARRIVAL -> b.requiredArrival();
            case TIMEZONE -> b.timezone(); case DURATION_MINUTES -> b.durationMinutes(); case ARRIVAL_LEAD_MINUTES -> b.arrivalLeadMinutes();
            case NAME -> b.name(); case PROVIDER -> b.provider(); case BOOKING_REFERENCE -> b.bookingReference();
            case MEETING_POINT -> b.meetingPoint(); case ENDING_POINT -> b.endingPoint(); case INSTRUCTIONS -> b.instructions();
            case CONDITIONS -> b.conditions().get(ref.conditionIndex());
        };
        var d = f.interpretation();
        var refs = new java.util.ArrayList<>(d.dependencies());
        if (d.calculation()!=null) refs.addAll(d.calculation().operands());
        return refs.stream().anyMatch(r -> affected(r,b,edits,visited));
    }
    private static void require(boolean value, String message) {
        if (!value) throw new InvalidDocumentExtractionException(message);
    }
}
