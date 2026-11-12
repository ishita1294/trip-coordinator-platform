package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;
import static com.ishita.tripcoordinatorplatform.ai.document.TourBookingFacts.*;

/** Distinct, inactive successor: source facts and clauses, not compressed V3 interpretation objects. */
public record SourceBookingExtraction(int schemaVersion, String contract, List<Source> sources,
        List<Booking> bookings, List<Clause> clauses, String coverageWarning) {
    public static final int SCHEMA_VERSION = 4;
    public static final String CONTRACT = "tour-source-facts-1";
    public static final String OUTPUT_NAME = "tour_source_facts_1";
    public SourceBookingExtraction { sources=copy(sources); bookings=copy(bookings); clauses=copy(clauses); }
    static <T> List<T> copy(List<T> values) { return values == null ? null : List.copyOf(values); }
    public record Source(int id, Integer page, String quotation) {}
    /** Normalized values and uncertainty are model decisions. No derivation prose or explanation sidecar. */
    public record Fact<T>(BookingFact.Status status, T value, List<T> alternatives,
            List<Integer> sources, String uncertainty) {
        public Fact { alternatives=copy(alternatives); sources=copy(sources); }
    }
    public record Booking(int index, Fact<String> name, Fact<String> provider, Fact<String> reference,
            Fact<String> timezone, Fact<DocumentLocation> meetingPoint, Fact<EndingLocation> endingPoint,
            Fact<SemanticBookingLifecycleV3.State> lifecycle,
            List<Fact<SemanticBookingLifecycleV3.BookingAssociation>> replaces,
            List<Participant> participants, List<ScheduleClaim> schedules,
            List<Projection> projections, List<Quantity> quantities) {
        public Booking { replaces=copy(replaces); participants=copy(participants); schedules=copy(schedules);
            projections=copy(projections); quantities=copy(quantities); }
    }
    public record Participant(Fact<String> category, Fact<Integer> count) {}
    public enum Target { START, END, REQUIRED_ARRIVAL, CLAUSE_ACTION }
    public enum Edge { POINT, LOWER, UPPER }
    public enum OffsetOperation { ADD_MINUTES, SUBTRACT_MINUTES }
    /** Exactly one literal or declared offset. References are booking-local; equality never implies projection. */
    public record Moment(LocalMoment literal, Offset offset) {}
    public record Offset(String schedule, Edge edge, OffsetOperation operation, int minutes,
            SemanticFactV3.TemporalBasis basis) {}
    public record Boundary(Moment moment, boolean inclusive) {}
    public record ScheduleValue(SemanticTemporalConstraintV3.Relation relation, Moment point,
            Boundary lower, Boundary upper, SemanticAssertionV3.Conditionality conditionality,
            List<Integer> conditionSources) {
        public ScheduleValue { conditionSources=copy(conditionSources); }
    }
    public enum ScheduleRole { ACTIVITY_SCHEDULE, TIMEZONE_EXPRESSION }
    /** Explicit source interpretation. No timezone/relationship is inferred from a name, city or prose. */
    public record ScheduleBinding(Fact<String> timezone, Fact<ScheduleRole> role,
            String equivalentTo, List<Integer> relationshipSources) {
        public ScheduleBinding { relationshipSources=copy(relationshipSources); }
    }
    public record ScheduleClaim(String id, Target target, Integer clauseIndex, Fact<ScheduleValue> fact,
            @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
            ScheduleBinding binding) {
        public ScheduleClaim(String id, Target target, Integer clauseIndex, Fact<ScheduleValue> fact) {
            this(id,target,clauseIndex,fact,null); // Old immutable records remain explicitly unbound.
        }
    }
    /** A boundary projection retains relation/inclusivity; it is never evidence that a window is an exact time. */
    public record Projection(Target field, String schedule, Edge edge) {}
    public enum QuantityKind { DURATION_MINUTES, ARRIVAL_LEAD_MINUTES, MINIMUM_AGE_YEARS, CANCELLATION_NOTICE_MINUTES }
    public record Difference(String fromSchedule, Edge fromEdge, String toSchedule, Edge toEdge,
            SemanticFactV3.TemporalBasis basis) {}
    /** Exactly one literal or declared MINUTES_BETWEEN; no claimed numeric result accompanies a calculation. */
    public record NumberValue(Integer literal, Difference difference) {}
    public record Quantity(QuantityKind kind, Integer clauseIndex, Fact<NumberValue> fact) {}
    public enum ClauseKind { INSTRUCTION, REQUIREMENT, RESTRICTION, POLICY, CANCELLATION_POLICY, INCLUSION, EXCLUSION, PARTICIPANTS, NOTICE, OTHER }
    public enum ScopeKind { DOCUMENT, ALL_BOOKINGS, BOOKINGS }
    public record Scope(ScopeKind kind, List<Integer> bookings) { public Scope { bookings=copy(bookings); } }
    /** Text is the referenced quotations, not a generated paraphrase/obligation qualification. */
    public record Clause(int index, ClauseKind kind, BookingFact.Status status, SemanticAssertionV3.Conditionality conditionality, List<Integer> sources,
            Fact<Scope> applicability, List<Integer> conditionSources, List<Integer> qualificationSources,
            String uncertainty) {
        public Clause { sources=copy(sources); conditionSources=copy(conditionSources); qualificationSources=copy(qualificationSources); }
    }
}
