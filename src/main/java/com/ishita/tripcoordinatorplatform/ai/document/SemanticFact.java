package com.ishita.tripcoordinatorplatform.ai.document;

import java.util.List;

/** An AI interpretation of source material, never independently verified documentary evidence. */
public record SemanticFact<T>(Status status, T value, List<T> alternatives,
                              List<Evidence> evidence, String uncertainty, Derivation derivation) {
    public SemanticFact {
        alternatives = alternatives == null ? null : List.copyOf(alternatives);
        evidence = evidence == null ? null : List.copyOf(evidence);
    }

    public enum Status { CLEAR, MISSING, AMBIGUOUS, CONFLICTING }
    public enum Basis { EXPLICIT, CONTEXT_INTERPRETED, CALCULATED, NOT_ESTABLISHED }
    public enum Field {
        NAME, PROVIDER, BOOKING_REFERENCE, START, END, TIMEZONE, DURATION_MINUTES,
        REQUIRED_ARRIVAL, ARRIVAL_LEAD_MINUTES, MEETING_POINT, ENDING_POINT, INSTRUCTIONS, CONDITIONS
    }
    public record Derivation(Basis basis, List<Field> inputs) {
        public Derivation { inputs = inputs == null ? null : List.copyOf(inputs); }
    }
    /** Quotation/page and association are model-reported; page is absent if not identifiable. */
    public record Evidence(int bookingIndex, String bookingAssociation, String quotation, Integer reportedPage) {}
}
